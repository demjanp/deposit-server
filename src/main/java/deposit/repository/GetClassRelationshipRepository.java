package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class GetClassRelationshipRepository extends BaseRepository {

	private static GetClassRelationshipRepository instance;
	
	private GetClassRelationshipRepository() {}
	
	public static synchronized GetClassRelationshipRepository getInstance() {
		if (instance == null) {
			instance = new GetClassRelationshipRepository();
		}
		return instance;
	}
	
	public Stream<Node[]> getClassRelationship(Transaction tx, String namespaceName, String startClassName, String endClassName, String label) {
		String matchQuery;
		Map<String, Object> params;
		
		if (endClassName.isEmpty() && label.isEmpty()) {
			matchQuery = """
				MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Class {name: $startClassName}),
					  (n)-[:CONTAINS]->(target:Class)
				MATCH (source)-[:RS]->(relationship:Relationship)-[:RE]->(target)
				RETURN source, target, relationship
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"startClassName", startClassName
			);
		} else if (endClassName.isEmpty()) {
			matchQuery = """
				MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Class {name: $startClassName}),
					  (n)-[:CONTAINS]->(target:Class)
				MATCH (source)-[:RS]->(relationship:Relationship {label: $label})-[:RE]->(target)
				RETURN source, target, relationship
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"startClassName", startClassName,
				"label", label
			);
		} else if (label.isEmpty()) {
			matchQuery = """
				MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Class {name: $startClassName}),
					  (n)-[:CONTAINS]->(target:Class {name: $endClassName})
				MATCH (source)-[:RS]->(relationship:Relationship)-[:RE]->(target)
				RETURN source, target, relationship
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"startClassName", startClassName,
				"endClassName", endClassName
			);
		} else {
			matchQuery = """
				MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Class {name: $startClassName}),
					  (n)-[:CONTAINS]->(target:Class {name: $endClassName})
				MATCH (source)-[:RS]->(relationship:Relationship {label: $label})-[:RE]->(target)
				RETURN source, target, relationship
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"startClassName", startClassName,
				"endClassName", endClassName,
				"label", label
			);
		}
		
		try {
			Result result = tx.execute(matchQuery, params);
			return StreamSupport.stream(result.stream().spliterator(), false)
					.map(r -> new Node[]{(Node) r.get("source"), (Node) r.get("target"), (Node) r.get("relationship")});
		} catch (Exception e) {
			throwException("Error retrieving Class Relationship", e);
		}
		return Stream.empty();		
	}
}
