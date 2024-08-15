package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;

public class GetClassRelationshipByIdRepository extends BaseRepository {

	private static GetClassRelationshipByIdRepository instance;
	
	private GetClassRelationshipByIdRepository() {}
	
	public static synchronized GetClassRelationshipByIdRepository getInstance() {
		if (instance == null) {
			instance = new GetClassRelationshipByIdRepository();
		}
		return instance;
	}
	
	public Node[] getClassRelationshipById(Transaction tx, String namespaceName, String relationshipId) {
		String matchQuery = """
			MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Class)-[:RS]->(relationship:Relationship {id: $relationshipId})-[:RE]->(target:Class)
			RETURN source, target, relationship
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
			throwException("Error retrieving Class Relationship", e);
		}
		return null;		
	}
}
