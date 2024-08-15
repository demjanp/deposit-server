package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
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
	
	public Stream<Node[]> getRelationship(Transaction tx, String namespaceName, String startObjectId, String endObjectId, String label) {
		String matchQuery;
		Map<String, Object> params;
		
		if (endObjectId.isEmpty() && label.isEmpty()) {
			matchQuery = """
				MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Object {id: $startObjectId}),
					  (n)-[:CONTAINS]->(target:Object)
				MATCH (source)-[:RS]->(relationship:Relationship)-[:RE]->(target)
				RETURN source, target, relationship
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"startObjectId", startObjectId
			);
		} else if (endObjectId.isEmpty()) {
			matchQuery = """
				MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Object {id: $startObjectId}),
					  (n)-[:CONTAINS]->(target:Object)
				MATCH (source)-[:RS]->(relationship:Relationship {label: $label})-[:RE]->(target)
				RETURN source, target, relationship
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
				MATCH (source)-[:RS]->(relationship:Relationship)-[:RE]->(target)
				RETURN source, target, relationship
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
				MATCH (source)-[:RS]->(relationship:Relationship {label: $label})-[:RE]->(target)
				RETURN source, target, relationship
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
					.map(r -> new Node[]{(Node) r.get("source"), (Node) r.get("target"), (Node) r.get("relationship")});
		} catch (Exception e) {
			throwException("Error retrieving Relationship", e);
		}
		return Stream.empty();
	}
}
