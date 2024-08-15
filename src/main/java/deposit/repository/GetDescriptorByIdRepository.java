package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;

public class GetDescriptorByIdRepository extends BaseRepository {

	private static GetDescriptorByIdRepository instance;
	
	private GetDescriptorByIdRepository() {}
	
	public static synchronized GetDescriptorByIdRepository getInstance() {
		if (instance == null) {
			instance = new GetDescriptorByIdRepository();
		}
		return instance;
	}
	
    public Node getDescriptorById(Transaction tx, String namespaceName, String descriptorId) {
		String matchQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object)-[:HAS_DESCRIPTOR]->(existing:Descriptor {id: $descriptorId}) RETURN existing LIMIT 1
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
			throwException("Error retrieving Descriptor: " + descriptorId, e);
		}
		return null;
    }
}
