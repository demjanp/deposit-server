package deposit.repository;

import org.neo4j.graphdb.Transaction;

import java.util.Map;

public class DeleteNamespaceRepository extends BaseRepository {

	private static DeleteNamespaceRepository instance;

	private DeleteNamespaceRepository() {}

	public static synchronized DeleteNamespaceRepository getInstance() {
		if (instance == null) {
			instance = new DeleteNamespaceRepository();
		}
		return instance;
	}

	public void deleteNamespace(Transaction tx, String namespaceName) {
		String deleteQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[r*0..]->(n)
			DETACH DELETE namespace, n
		""";
		try {
			tx.execute(deleteQuery, Map.of("namespaceName", namespaceName));
		} catch (Exception e) {
			throwException("Error deleting Namespace with name: " + namespaceName, e);
		}
	}
}
