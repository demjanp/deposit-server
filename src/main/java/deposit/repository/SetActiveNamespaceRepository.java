package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.Map;

public class SetActiveNamespaceRepository extends BaseRepository {

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
		
		String queryUpdate = """
			MATCH (namespace:Namespace {name: $name})
			MERGE (namespace)-[:CONTAINS]->(archive:Archive)
			WITH namespace, archive
		""";
		
		if (versioningType.equals("transient")) {
			queryUpdate += Versioning.getClearArchiveCode();
		}
		queryUpdate += Versioning.getCreateArchiveCode(versioningType);
		queryUpdate += "RETURN namespace";
		
		Map<String, Object> params = Map.of(
			"name", name,
			"timeStamp", timeStamp,
			"username", username
		);

		try {
			Result result = tx.execute(queryUpdate, params);
			if (result.hasNext()) {
				Node node = (Node) result.next().get("namespace");
				return node;
			} else {
				throwException("Namespace update failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error executing transaction", e);
		}
		
		return null;
	}
}
