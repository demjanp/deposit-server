package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;

public class GetObjectClassDescriptorByIdRepository extends BaseRepository {

	private static GetObjectClassDescriptorByIdRepository instance;
	
	private GetObjectClassDescriptorByIdRepository() {}
	
	public static synchronized GetObjectClassDescriptorByIdRepository getInstance() {
		if (instance == null) {
			instance = new GetObjectClassDescriptorByIdRepository();
		}
		return instance;
	}
	
    public Node getObjectClassDescriptorById(Transaction tx, String namespaceName, String descriptorId) {
		String matchQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(object:Object)-[:HAS_DESCRIPTOR]->(descriptor:Descriptor {id: $descriptorId}), (object)-[:MEMBER_OF]->(:Class)-[:HAS_DESCRIPTOR]->(existing:Descriptor {name: descriptor.name}) RETURN existing
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
			throwException("Error retrieving Class Descriptor", e);
		}
		return null;
    }
}
