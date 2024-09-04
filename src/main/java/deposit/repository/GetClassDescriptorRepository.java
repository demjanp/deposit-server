package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class GetClassDescriptorRepository extends BaseRepository {

	private static GetClassDescriptorRepository instance;
	
	private GetClassDescriptorRepository() {}
	
	public static synchronized GetClassDescriptorRepository getInstance() {
		if (instance == null) {
			instance = new GetClassDescriptorRepository();
		}
		return instance;
	}
	
    public Stream<Node> getClassDescriptor(Transaction tx, String namespaceName, String className, String descriptorName) {
		String matchQuery;
		Map<String, Object> params;
		if (descriptorName.isEmpty()) {
			matchQuery = """
				MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class {name: $className})-[:HAS_DESCRIPTOR]->(existing:Descriptor)
				RETURN existing
				ORDER BY existing.order
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"className", className
			);
		} else {
			matchQuery = """
				MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class {name: $className})-[:HAS_DESCRIPTOR]->(existing:Descriptor {name: $descriptorName}) RETURN existing
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"className", className,
				"descriptorName", descriptorName
			);
		}
		
		try {
			Result result = tx.execute(matchQuery, params);
			return StreamSupport.stream(result.stream().spliterator(), false)
					.map(r -> (Node) r.get("existing"));
		} catch (Exception e) {
			throwException("Error retrieving Class Descriptor with name: " + descriptorName, e);
		}
		return Stream.empty();
    }
}
