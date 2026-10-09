package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;

public class GetClassDescriptorByIdRepository extends BaseRepository {

	private static GetClassDescriptorByIdRepository instance;
	
	private GetClassDescriptorByIdRepository() {}
	
	public static synchronized GetClassDescriptorByIdRepository getInstance() {
		if (instance == null) {
			instance = new GetClassDescriptorByIdRepository();
		}
		return instance;
	}
	
	public Node getClassDescriptorById(Transaction tx, String namespaceName, String descriptorId) {
		String matchQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class)-[:HAS_DESCRIPTOR]->(existing:Descriptor {id: $descriptorId}) RETURN existing LIMIT 1
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"descriptorId", descriptorId
		);
		
		try {
			Result result = tx.execute(matchQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("existing");
			}
		} catch (Exception e) {
			throwException("Error retrieving Class Descriptor: " + descriptorId, e);
		}
		return null;
	}
}
