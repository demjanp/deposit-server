package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class GetActiveNamespaceRepository extends BaseRepository {

	private static GetActiveNamespaceRepository instance;

	private GetActiveNamespaceRepository() {}

	public static synchronized GetActiveNamespaceRepository getInstance() {
		if (instance == null) {
			instance = new GetActiveNamespaceRepository();
		}
		return instance;
	}

	public Node getActiveNamespace(Transaction tx, String namespaceName) {
		String validateNameQuery = """
			MATCH (namespace:Namespace {name: $namespaceName}) RETURN namespace
		""";

		try {
			Result result = tx.execute(validateNameQuery, Map.of("namespaceName", namespaceName));
			
			if (result.hasNext()) {
				return (Node) result.next().get("namespace");
			} else {
				throwException("Transaction failed: Namespace " + namespaceName + " doesn't exist");
			}
		} catch (Exception e) {
			throwException("Error executing transaction", e);
		}
		return null;
	}
}
