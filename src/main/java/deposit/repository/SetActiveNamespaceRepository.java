package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

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

	public boolean setActiveNamespace(Transaction tx, ZonedDateTime timeStamp, String username, String name) {
		String validateNameQuery = """
			MATCH (namespace:Namespace {name: $name}) RETURN namespace
		""";
		/*
		TODO
		if Namespace exists:
			if versioningType is 'transient' delete the archive subtree, leaving only the Archive node
			if versioningType is not '' check if the archive subtree exists and if not, create it by cloning all elements from the Namespace and placing them under the Archive node
			return the Namespace node
		else:
			return null
		*/

		try {
			Result result = tx.execute(validateNameQuery, Map.of("name", name));
			
			if (result.hasNext()) {
				return true;
			} else {
				throwException("Transaction failed: Namespace " + name + " doesn't exist");
				return false;
			}
		} catch (Exception e) {
			throwException("Error executing transaction", e);
		}
		return false;
	}
}
