package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class GetClassMembersRepository extends BaseRepository {

	private static GetClassMembersRepository instance;
	
	private GetClassMembersRepository() {}
	
	public static synchronized GetClassMembersRepository getInstance() {
		if (instance == null) {
			instance = new GetClassMembersRepository();
		}
		return instance;
	}
	
    public Stream<Node> getClassMembers(Transaction tx, String namespaceName, String className) {
		String matchQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:Object)-[:MEMBER_OF]->(:Class {name: $className}) RETURN existing
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"className", className
		);
		
		try {
			Result result = tx.execute(matchQuery, params);
			return StreamSupport.stream(result.stream().spliterator(), false)
					.map(r -> (Node) r.get("existing"));
		} catch (Exception e) {
			throwException("Error retrieving Members of  Class: " + className, e);
		}
		return Stream.empty();
    }
}
