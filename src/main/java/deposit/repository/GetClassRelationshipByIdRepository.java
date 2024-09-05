package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;
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
	
	public Relationship getClassRelationshipById(Transaction tx, String namespaceName, String relationshipId) {
		String matchQuery = """
			MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class)-[relationship:RELATED_TO {id: $relationshipId}]->(:Class)
			RETURN relationship
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
			throwException("Error retrieving Class Relationship", e);
		}
		return null;		
	}
}
