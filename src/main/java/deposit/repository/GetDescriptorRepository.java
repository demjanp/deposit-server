package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class GetDescriptorRepository extends BaseRepository {

	private static GetDescriptorRepository instance;
	
	private GetDescriptorRepository() {}
	
	public static synchronized GetDescriptorRepository getInstance() {
		if (instance == null) {
			instance = new GetDescriptorRepository();
		}
		return instance;
	}
	
    public Stream<Node> getDescriptor(Transaction tx, String namespaceName, String objectId, String descriptorName) {
		String matchQuery;
		Map<String, Object> params;
		if (descriptorName.isEmpty()) {
			matchQuery = """
				MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object {id: $objectId})-[:HAS_DESCRIPTOR]->(existing:Descriptor) RETURN existing
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"objectId", objectId
			);
		} else {
			matchQuery = """
				MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object {id: $objectId})-[:HAS_DESCRIPTOR]->(existing:Descriptor {name: $descriptorName}) RETURN existing
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"objectId", objectId,
				"descriptorName", descriptorName
			);
		}
		
		try {
			Result result = tx.execute(matchQuery, params);
			return StreamSupport.stream(result.stream().spliterator(), false)
					.map(r -> (Node) r.get("existing"));
		} catch (Exception e) {
			throwException("Error retrieving Descriptor with name: " + descriptorName, e);
		}
		return Stream.empty();
    }
}
