package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.util.Map;

public class DeleteUserRepository extends BaseRepository {

	private static DeleteUserRepository instance;
	
	private DeleteUserRepository() {}
	
	public static synchronized DeleteUserRepository getInstance() {
		if (instance == null) {
			instance = new DeleteUserRepository();
		}
		return instance;
	}
	
	public void deleteUser(Transaction tx, String name) {
		String validateQuery = """
			MATCH (existing:User {username: $name}) RETURN existing
		""";
		Map<String, Object> params;
		params = Map.of("name", name);
		try {
			Result validation = tx.execute(validateQuery, params);
			if (!validation.hasNext()) {
				throwException("Error deleting the User node " + name + ". It does not exist.");
			}
		} catch (Exception e) {
			throwException("Error deleting the User node " + name, e);
		}
		
		String deleteQuery = """
			MATCH (updated:User {username: $name})
			DETACH DELETE updated
			RETURN updated
		""";
		params = Map.of("name", name);
		try {
			tx.execute(deleteQuery, params);
		} catch (Exception e) {
			throwException("Error deleting the User node " + name, e);
		}
	}
}
