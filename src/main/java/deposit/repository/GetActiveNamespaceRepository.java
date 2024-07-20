package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class GetActiveNamespaceRepository extends BaseRepository<GetActiveNamespaceRepository> {

	private static GetActiveNamespaceRepository instance;

	private GetActiveNamespaceRepository() {}

	public static synchronized GetActiveNamespaceRepository getInstance() {
		if (instance == null) {
			instance = new GetActiveNamespaceRepository();
		}
		return instance;
	}

	public Node getActiveNamespace(Transaction tx, String name) {
		String validateNameQuery = """
			MATCH (namespace:Namespace {name: $name}) RETURN namespace
		""";

		try {
			Result result = tx.execute(validateNameQuery, Map.of("name", name));
			
			if (result.hasNext()) {
				Node node = (Node) result.next().get("namespace");
				return node;
			} else {
				throwException("Transaction failed: Namespace " + name + " doesn't exist");
			}
		} catch (Exception e) {
			throwException("Error executing transaction", e);
		}
		return null;
	}
}
