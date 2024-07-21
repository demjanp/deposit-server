package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.Map;

public class SetActiveNamespaceRepository extends BaseRepository<SetActiveNamespaceRepository> {

	private static SetActiveNamespaceRepository instance;

	private SetActiveNamespaceRepository() {}

	public static synchronized SetActiveNamespaceRepository getInstance() {
		if (instance == null) {
			instance = new SetActiveNamespaceRepository();
		}
		return instance;
	}

	public Node setActiveNamespace(Transaction tx, ZonedDateTime timeStamp, String username, String name) {
		String queryMatch = """
			MATCH (namespace:Namespace {name: $name}) RETURN namespace
		""";
		
		String versioningType = "";
		Node namespace = null;
		
		try {
			Result result = tx.execute(queryMatch, Map.of("name", name));
			
			if (result.hasNext()) {
				namespace = (Node) result.next().get("namespace");
				versioningType = (String) namespace.getProperty("versioningType");
			} else {
				throwException("Transaction failed: Namespace " + name + " doesn't exist");
			}
		} catch (Exception e) {
			throwException("Error executing transaction", e);
		}
		
		try {
			String queryUpdate = """
				MATCH (namespace:Namespace {name: $name})
			""";
			queryUpdate += Versioning.getNamespaceUpdateCode(versioningType);
			Map<String, Object> params = Map.of(
				"name", name,
				"timeStamp", timeStamp,
				"username", username
			);
			tx.execute(queryUpdate, params);
			return namespace;
		} catch (Exception e) {
			throwException("Error executing transaction", e);
		}
		
		return null;
	}
}
