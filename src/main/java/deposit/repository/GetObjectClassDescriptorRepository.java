package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;

public class GetObjectClassDescriptorRepository extends BaseRepository {

	private static GetObjectClassDescriptorRepository instance;
	
	private GetObjectClassDescriptorRepository() {}
	
	public static synchronized GetObjectClassDescriptorRepository getInstance() {
		if (instance == null) {
			instance = new GetObjectClassDescriptorRepository();
		}
		return instance;
	}
	
	public Node getObjectClassDescriptor(Transaction tx, String namespaceName, String objectId, String descriptorName) {
		String matchQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object {id: $objectId})-[:MEMBER_OF]->(:Class)-[:HAS_DESCRIPTOR]->(existing:Descriptor {name: $descriptorName}) RETURN existing
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"objectId", objectId,
			"descriptorName", descriptorName
		);
		
		try {
			Result result = tx.execute(matchQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("existing");
			}
		} catch (Exception e) {
			throwException("Error retrieving Class Descriptor for Object: " + objectId, e);
		}
		return null;
	}
}
