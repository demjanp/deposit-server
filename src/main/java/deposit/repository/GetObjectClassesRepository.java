package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class GetObjectClassesRepository extends BaseRepository {

	private static GetObjectClassesRepository instance;
	
	private GetObjectClassesRepository() {}
	
	public static synchronized GetObjectClassesRepository getInstance() {
		if (instance == null) {
			instance = new GetObjectClassesRepository();
		}
		return instance;
	}
	
    public Stream<Node> getObjectClasses(Transaction tx, String namespaceName, String objectId) {
		String matchQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object {id: $objectId})-[:MEMBER_OF]->(existing:Class) RETURN existing
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"objectId", objectId
		);
		
		try {
			Result result = tx.execute(matchQuery, params);
			return StreamSupport.stream(result.stream().spliterator(), false)
					.map(r -> (Node) r.get("existing"));
		} catch (Exception e) {
			throwException("Error retrieving Classes for Object: " + objectId, e);
		}
		return Stream.empty();
    }
}
