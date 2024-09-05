package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;
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
	
	public Relationship getRelationshipById(Transaction tx, String namespaceName, String relationshipId) {
		String matchQuery = """
			MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object)-[relationship:RELATED_TO {id: $relationshipId}]->(:Object)
			RETURN relationship LIMIT 1
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"relationshipId", relationshipId
		);
	
		try {
			Result result = tx.execute(matchQuery, params);
			if (result.hasNext()) {
				return (Relationship) result.next().get("relationship");
			}
		} catch (Exception e) {
			throwException("Error retrieving Relationship", e);
		}
		return null;
	}
}
