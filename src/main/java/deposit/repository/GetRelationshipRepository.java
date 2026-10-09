package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Result;

import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class GetRelationshipRepository extends BaseRepository {

	private static GetRelationshipRepository instance;
	
	private GetRelationshipRepository() {}
	
	public static synchronized GetRelationshipRepository getInstance() {
		if (instance == null) {
			instance = new GetRelationshipRepository();
		}
		return instance;
	}
	
	public Stream<Relationship> getRelationship(Transaction tx, String namespaceName, String startObjectId, String endObjectId, String label) {
		String matchQuery;
		Map<String, Object> params;
		
		if (endObjectId.isEmpty() && label.isEmpty()) {
			matchQuery = """
				MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Object {id: $startObjectId}),
					  (n)-[:CONTAINS]->(target:Object)
				MATCH (source)-[relationship:RELATED_TO]->(target)
				RETURN relationship
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"startObjectId", startObjectId
			);
		} else if (endObjectId.isEmpty()) {
			matchQuery = """
				MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Object {id: $startObjectId}),
					  (n)-[:CONTAINS]->(target:Object)
				MATCH (source)-[relationship:RELATED_TO {label: $label}]->(target)
				RETURN relationship
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"startObjectId", startObjectId,
				"label", label
			);
		} else if (label.isEmpty()) {
			matchQuery = """
				MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Object {id: $startObjectId}),
					  (n)-[:CONTAINS]->(target:Object {id: $endObjectId})
				MATCH (source)-[relationship:RELATED_TO]->(target)
				RETURN relationship
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"startObjectId", startObjectId,
				"endObjectId", endObjectId
			);
		} else {
			matchQuery = """
				MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Object {id: $startObjectId}),
					  (n)-[:CONTAINS]->(target:Object {id: $endObjectId})
				MATCH (source)-[relationship:RELATED_TO {label: $label}]->(target)
				RETURN relationship
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"startObjectId", startObjectId,
				"endObjectId", endObjectId,
				"label", label
			);
		}
		
		try {
			Result result = tx.execute(matchQuery, params);
			return StreamSupport.stream(result.stream().spliterator(), false)
					.map(r -> (Relationship) r.get("relationship"));
		} catch (Exception e) {
			throwException("Error retrieving Relationship", e);
		}
		return Stream.empty();
	}
}
