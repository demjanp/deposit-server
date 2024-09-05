package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class EnsureClassRelationshipDeletionRepository extends BaseRepository {

	private static EnsureClassRelationshipDeletionRepository instance;
	
	private EnsureClassRelationshipDeletionRepository() {}
	
	public static synchronized EnsureClassRelationshipDeletionRepository getInstance() {
		if (instance == null) {
			instance = new EnsureClassRelationshipDeletionRepository();
		}
		return instance;
	}
	
	public Stream<String> getObjectRelationshipIds(Transaction tx, String namespaceName, String startClassName, String endClassName, String label) {
		String matchQuery = """
			MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Object)-[:MEMBER_OF]->(:Class {name: $startClassName}), (n)-[:CONTAINS]->(target:Object)-[:MEMBER_OF]->(:Class {name: $endClassName})
			MATCH (source:Object)-[relationship:RELATED_TO {label: $label}]->(target:Object)
			RETURN relationship.id AS relationshipId
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"startClassName", startClassName,
			"endClassName", endClassName,
			"label", label
		);
		try {
			Result result = tx.execute(matchQuery, params);
			return StreamSupport.stream(result.stream().spliterator(), false)
					.map(r -> r.get("relationshipId").toString());
		} catch (Exception e) {
			throw new RuntimeException("Error retrieving Relationship Ids", e);
		}
	}
}

